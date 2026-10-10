/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;

public class ManagedEncodedValueTest {
    @Test public void packedViewsHandleSignedWidthsEndiannessAndUnalignedOffsets() {
        for (int width : new int[]{2, 4, 8}) {
            for (int order : new int[]{ManagedPackedValue.LITTLE_ENDIAN,
                                        ManagedPackedValue.BIG_ENDIAN}) {
                byte[] data = new byte[16];
                Arrays.fill(data, (byte) 0x5a);
                long value = width == 2 ? -1234L : width == 4 ? -1234567L : -0x1122334455667788L;
                ManagedPackedValue.write(data, 3, width, order, value);
                assertEquals(value, ManagedPackedValue.read(data, 3, width, order));
                assertEquals((byte) 0x5a, data[2]);
                assertEquals((byte) 0x5a, data[3 + width]);
                assertFalse(ManagedPackedValue.valid(data, 17 - width, width));
            }
        }
    }

    @Test public void overlappingViewsUseConcreteArrayIdentity() {
        byte[] first = new byte[8], second = new byte[8];
        assertTrue(ManagedPackedValue.overlaps(first, 1, 4, first, 4, 4));
        assertFalse(ManagedPackedValue.overlaps(first, 0, 2, first, 2, 4));
        assertFalse(ManagedPackedValue.overlaps(first, 1, 4, second, 1, 4));
    }

    @Test public void findsUnknownSubtractionConstant() {
        int constant = 58_279_306;
        int first = 500, next = 450, third = 400;
        int r1 = constant - first, r2 = constant - next, r3 = constant - third;
        int mask = ManagedEncodedValue.infer(first, r1, next, r2);
        assertTrue((mask & ManagedEncodedValue.SUBTRACT) != 0);
        mask = ManagedEncodedValue.refineMask(mask, next, r2, third, r3);
        assertEquals(ManagedEncodedValue.SUBTRACT, mask);
        int key = ManagedEncodedValue.key(ManagedEncodedValue.SUBTRACT, first, r1);
        assertEquals(constant, key);
        assertEquals(999_999, ManagedEncodedValue.decode(ManagedEncodedValue.SUBTRACT,
                ManagedEncodedValue.encode(ManagedEncodedValue.SUBTRACT, 999_999, key), key));
    }

    @Test public void xorAndAdditiveRoundTrip() {
        for (int family : new int[]{ManagedEncodedValue.XOR, ManagedEncodedValue.ADD}) {
            int key = family == ManagedEncodedValue.XOR ? 0x6f45a312 : 12345;
            int first = ManagedEncodedValue.encode(family, 35, key);
            int next = ManagedEncodedValue.encode(family, 27, key);
            assertTrue((ManagedEncodedValue.infer(35, first, 27, next) & family) != 0);
            int inferred = ManagedEncodedValue.key(family, 35, first);
            assertEquals(600_000, ManagedEncodedValue.decode(family,
                    ManagedEncodedValue.encode(family, 600_000, inferred), inferred));
        }
    }

    @Test public void unalignedByteWindowIsFound() {
        byte[] before = new byte[15], after = new byte[15];
        int constant = 58_279_306;
        ManagedPackedValue.write(before, 3, 4, 0, constant - 500);
        ManagedPackedValue.write(after, 3, 4, 0, constant - 450);
        ArrayList<Integer> offsets = new ArrayList<>();
        int result = ManagedEncodedByteScan.correlate(before, after, 0,
                500, 450, 100, () -> true, (offset, oldBits, newBits, mask) -> {
                    if ((mask & ManagedEncodedValue.SUBTRACT) != 0) offsets.add(offset);
                });
        assertTrue(result > 0);
        assertTrue(offsets.contains(3));
    }

    @Test public void budgetAndCancellationAreReported() {
        byte[] before = new byte[1000], after = new byte[1000];
        after[0] = 1;
        assertEquals(-1, ManagedEncodedByteScan.correlate(before, after, 0, 1, 2, 10,
                () -> false, (offset, oldBits, newBits, mask) -> fail("cancelled")));
        assertEquals(-2, ManagedEncodedByteScan.correlate(before, after, 0, 1, 2, 0,
                () -> true, (offset, oldBits, newBits, mask) -> fail("budget exceeded")));
    }
}
