/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.memory;

/** Fixed-parameter transforms over signed Java int values (modulo 2^32). */
final class ManagedEncodedValue {
    static final int ADD = 1;
    static final int SUBTRACT = 2;
    static final int XOR = 4;

    private ManagedEncodedValue() {}

    static int infer(int visibleBefore, int rawBefore, int visibleNow, int rawNow) {
        if (visibleBefore == visibleNow) return 0;
        int mask = 0;
        if (rawBefore - visibleBefore == rawNow - visibleNow) mask |= ADD;
        if (rawBefore + visibleBefore == rawNow + visibleNow) mask |= SUBTRACT;
        if ((rawBefore ^ visibleBefore) == (rawNow ^ visibleNow)) mask |= XOR;
        return mask;
    }

    static int key(int family, int visible, int raw) {
        switch (family) {
            case ADD: return raw - visible;
            case SUBTRACT: return raw + visible;
            case XOR: return raw ^ visible;
            default: throw new IllegalArgumentException("Unrecognized encoding");
        }
    }

    static int encode(int family, int visible, int key) {
        switch (family) {
            case ADD: return visible + key;
            case SUBTRACT: return key - visible;
            case XOR: return visible ^ key;
            default: throw new IllegalArgumentException("Unrecognized encoding");
        }
    }

    static int decode(int family, int raw, int key) {
        switch (family) {
            case ADD: return raw - key;
            case SUBTRACT: return key - raw;
            case XOR: return raw ^ key;
            default: throw new IllegalArgumentException("Unrecognized encoding");
        }
    }

    static int refineMask(int priorMask, int visibleBefore, int rawBefore,
                          int visibleNow, int rawNow) {
        return priorMask & infer(visibleBefore, rawBefore, visibleNow, rawNow);
    }
}
