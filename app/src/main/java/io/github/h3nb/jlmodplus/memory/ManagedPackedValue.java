/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.memory;

/** Signed numeric views over managed byte arrays. */
final class ManagedPackedValue {
    static final int LITTLE_ENDIAN = 0;
    static final int BIG_ENDIAN = 1;

    private ManagedPackedValue() {}

    static boolean valid(byte[] data, int offset, int width) {
        return data != null && (width == 2 || width == 4 || width == 8)
                && offset >= 0 && offset <= data.length - width;
    }

    static long read(byte[] data, int offset, int width, int endian) {
        check(data, offset, width, endian);
        long bits = 0L;
        for (int index = 0; index < width; index++) {
            int shift = 8 * (endian == LITTLE_ENDIAN ? index : width - 1 - index);
            bits |= (data[offset + index] & 0xffL) << shift;
        }
        if (width == 2) return (short) bits;
        if (width == 4) return (int) bits;
        return bits;
    }

    static void write(byte[] data, int offset, int width, int endian, long value) {
        check(data, offset, width, endian);
        if (width == 2 && (short) value != value
                || width == 4 && (int) value != value) {
            throw new IllegalArgumentException("Value is outside the numeric view");
        }
        for (int index = 0; index < width; index++) {
            int shift = 8 * (endian == LITTLE_ENDIAN ? index : width - 1 - index);
            data[offset + index] = (byte) (value >>> shift);
        }
    }

    static boolean overlaps(byte[] first, int firstOffset, int firstWidth,
                            byte[] second, int secondOffset, int secondWidth) {
        return first == second && first != null
                && firstOffset >= 0 && secondOffset >= 0
                && firstWidth > 0 && secondWidth > 0
                && (long) firstOffset < (long) secondOffset + secondWidth
                && (long) secondOffset < (long) firstOffset + firstWidth;
    }

    private static void check(byte[] data, int offset, int width, int endian) {
        if (!valid(data, offset, width)
                || endian != LITTLE_ENDIAN && endian != BIG_ENDIAN) {
            throw new IllegalArgumentException("Invalid packed numeric view");
        }
    }
}
