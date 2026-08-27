/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.memory;

/**
 * Tiny target-side native probe. It owns no scanner/candidate state: only process identity,
 * disposable cross-process capability probe data, and compressed resident ART page runs.
 */
public final class NativeTargetProbe {
    static {
        System.loadLibrary("jlprobe");
    }

    private NativeTargetProbe() {}

    public static native long probeAddress();
    public static native long probeValue();
    public static native int pageSize();

    /** Fills [count, flags, start0, end0, ...]; flags bit 0 means truncated. */
    public static native int fillResidentJavaRuns(long[] output, int scope, int maxRuns);
}
