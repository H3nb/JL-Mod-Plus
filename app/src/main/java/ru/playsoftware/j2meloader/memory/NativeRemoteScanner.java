/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory;

/** Native scanner whose candidate DB lives in :memory_engine and targets :midlet by PID. */
final class NativeRemoteScanner {
    static {
        System.loadLibrary("jlremote");
    }

    static final int RESULT_OK = 0;
    static final int RESULT_CANCELLED = 1;
    static final int RESULT_INVALID_QUERY = 2;
    static final int RESULT_RESOURCE_LIMIT = 3;
    static final int RESULT_NO_RANGES = 4;
    static final int RESULT_NO_MATCHES = 5;

    private NativeRemoteScanner() {}

    static native String nativeConfigureTarget(int targetPid, int pageSize, long[] residentRuns);
    static native int nativeSearch(String value, int scope, int valueType);
    static native int nativeRefine(String value);
    static native int nativeRefineRelocating(String value);
    static native boolean nativeCanRelocate();
    static native void nativeCommitZero();
    static native long nativeGetResultCount();
    static native int nativeFillResultsPage(long[] output, int offset, int limit);
    static native String nativeEdit(long address, int valueType, String expected, String replacement);
    static native String nativeGetDiagnostics();
    static native String nativeGetLastError();
    static native void nativeClear();
    static native void nativeCancel();
}
