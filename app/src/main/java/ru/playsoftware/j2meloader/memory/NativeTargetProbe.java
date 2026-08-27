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
 * Tiny target-side native probe used only to prove same-UID cross-process memory access.
 * It intentionally owns no scanner state and performs no heap scan.
 */
public final class NativeTargetProbe {
    static {
        System.loadLibrary("jlprobe");
    }

    private NativeTargetProbe() {}

    public static native long probeAddress();
    public static native long probeValue();
    public static native int pageSize();
}
