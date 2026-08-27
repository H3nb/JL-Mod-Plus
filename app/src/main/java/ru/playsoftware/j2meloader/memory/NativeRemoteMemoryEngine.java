/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory;

final class NativeRemoteMemoryEngine {
    static {
        System.loadLibrary("jlremote");
    }

    private NativeRemoteMemoryEngine() {}

    static native String capabilityTest(int targetPid, long probeAddress, long expectedValue);
}
