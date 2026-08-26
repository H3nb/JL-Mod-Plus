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

    static {
        System.loadLibrary("memory_scan");
    }

    private NativeMemoryAgent() {
    }

    static native String nativeSelfTest();

    static native int nativeSearch(String value, int scope, int valueType);

    static native int nativeRefine(String value);

    /**
     * Rebuilds a fresh exact-match pool and commits only confidently matched logical address groups.
     * A completed recovery with no logical matches commits an empty result set.
     */
    static native int nativeRefineRelocating(String value);

    static native long nativeGetResultCount();

    /** Fills [count, address, type, readable(0/1), valueBits, ...] into a reusable array. */
    static native int nativeFillResultsPage(long[] output, int offset, int limit);

    static native String nativeEdit(long address, int valueType, String expected, String replacement);

    static native String nativeGetDiagnostics();

    static native String nativeGetLastError();

    static native void nativeCancel();

    static native void nativeClear();
}