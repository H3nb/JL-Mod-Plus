/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;

/**
 * Debug prototype process boundary for future out-of-target scanning.
 * The first gate is capability only: prove same-UID process_vm_readv/writev against :midlet.
 */
public final class MemoryEngineService extends Service {
    private final IMemoryEngineService.Stub binder = new IMemoryEngineService.Stub() {
        @Override
        public String runCapabilityTest(int targetPid, long probeAddress, long expectedValue) {
            try {
                return NativeRemoteMemoryEngine.capabilityTest(targetPid, probeAddress, expectedValue);
            } catch (RuntimeException | UnsatisfiedLinkError error) {
                return "remoteEngineSupported=false\nremoteError="
                        + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
        }

        @Override
        public int getEnginePid() {
            return Process.myPid();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
