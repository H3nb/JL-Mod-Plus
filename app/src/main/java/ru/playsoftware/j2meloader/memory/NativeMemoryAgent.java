/*
 * Copyright 2026 JL-Mod Plus contributors
 *
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

final class NativeMemoryAgent {
    static final int SCOPE_JAVA_FAST = 0;
    static final int SCOPE_JAVA_THOROUGH = 1;

    static final int RESULT_OK = 0;
    static final int RESULT_CANCELLED = 1;
    static final int RESULT_INVALID_QUERY = 2;
    static final int RESULT_RESOURCE_LIMIT = 3;
    static final int RESULT_NO_RANGES = 4;
    static final int RESULT_NO_MATCHES = 5;

    /**
     * The service serializes search/refine work on one worker. This flag only avoids repeating a
     * direct refine when the service has already observed RESULT_NO_MATCHES and immediately asks
     * for relocation recovery. A New Search always resets it.
     */
    private static volatile boolean directNoMatchPending;

    static {
        System.loadLibrary("memory_scan");
    }

    private NativeMemoryAgent() {
    }

    static native String nativeSelfTest();

    static int nativeSearch(String value, int scope, int valueType) {
        directNoMatchPending = false;
        return nativeSearchRaw(value, scope, valueType);
    }

    static int nativeRefine(String value) {
        int result = nativeRefineRaw(value);
        directNoMatchPending = result == RESULT_NO_MATCHES;
        return result;
    }

    /**
     * Prefer the retained raw addresses even if ART's GC counter increased. A GC event is not proof
     * that an object moved (confirmed by the Android 11 control). Relocation is attempted only after
     * direct refinement actually loses every match. If the service already performed that direct
     * no-match pass, skip repeating it and go straight to the relocation fallback.
     */
    static int nativeRefineRelocating(String value) {
        if (!directNoMatchPending) {
            int direct = nativeRefineRaw(value);
            if (direct != RESULT_NO_MATCHES) {
                directNoMatchPending = false;
                return direct;
            }
        }
        directNoMatchPending = false;
        return nativeRefineRelocatingRaw(value);
    }

    private static native int nativeSearchRaw(String value, int scope, int valueType);

    private static native int nativeRefineRaw(String value);

    private static native int nativeRefineRelocatingRaw(String value);

    static native long nativeGetResultCount();

    /** Fills [count, address, type, readable(0/1), valueBits, ...] into a reusable array. */
    static native int nativeFillResultsPage(long[] output, int offset, int limit);

    static native String nativeEdit(long address, int valueType, String expected, String replacement);

    static native String nativeGetDiagnostics();

    static native String nativeGetLastError();

    static native void nativeCancel();

    static native void nativeClear();
}
