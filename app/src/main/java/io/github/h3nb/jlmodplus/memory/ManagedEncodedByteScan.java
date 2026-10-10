/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.memory;

/** Non-allocating per-window correlation pass for a selected Int32 byte-order view. */
final class ManagedEncodedByteScan {
    interface Check { boolean active(); }
    interface Match { void accept(int offset, int initialRaw, int currentRaw, int familyMask); }

    private ManagedEncodedByteScan() {}

    /** Returns -1 on cancellation and -2 on match-budget exhaustion. */
    static int correlate(byte[] before, byte[] now, int endian, int visibleBefore,
                         int visibleNow, int maxMatches, Check check, Match sink) {
        if (before == null || now == null || before.length != now.length || maxMatches < 0
                || check == null || sink == null
                || endian != ManagedPackedValue.LITTLE_ENDIAN
                && endian != ManagedPackedValue.BIG_ENDIAN) {
            throw new IllegalArgumentException("Invalid encoded scan inputs");
        }
        int count = 0;
        for (int offset = 0; offset <= before.length - 4; offset++) {
            if ((offset & 255) == 0 && !check.active()) return -1;
            int previous = (int) ManagedPackedValue.read(before, offset, 4, endian);
            int current = (int) ManagedPackedValue.read(now, offset, 4, endian);
            if (previous == current) continue;
            int mask = ManagedEncodedValue.infer(visibleBefore, previous, visibleNow, current);
            if (mask == 0) continue;
            if (count == maxMatches) return -2;
            sink.accept(offset, previous, current, mask);
            count++;
        }
        return count;
    }
}
