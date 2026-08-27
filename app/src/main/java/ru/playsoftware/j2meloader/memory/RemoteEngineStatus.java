/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory;

/** Process-local diagnostic snapshot populated by the debug remote-engine capability client. */
public final class RemoteEngineStatus {
    private static volatile String diagnostics = "remoteEngineSupported=UNKNOWN";
    private static volatile boolean supported;

    private RemoteEngineStatus() {}

    public static void update(String value) {
        diagnostics = value == null || value.isBlank()
                ? "remoteEngineSupported=false\nremoteError=empty capability result"
                : value;
        supported = diagnostics.contains("remoteEngineSupported=true");
    }

    public static String diagnostics() {
        return diagnostics;
    }

    public static boolean supported() {
        return supported;
    }
}
