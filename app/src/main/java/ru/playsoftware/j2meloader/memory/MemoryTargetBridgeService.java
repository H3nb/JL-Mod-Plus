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
 * Thin :midlet bridge. It exposes target identity and compressed resident ART page runs only;
 * scanning/candidate ownership intentionally belongs elsewhere.
 */
public final class MemoryTargetBridgeService extends Service {
    private static final int MAX_RUNS = 2048;
    private static final long[] EMPTY = new long[0];
    private final Object runsLock = new Object();
    private final long[] residentRuns = new long[2 + MAX_RUNS * 2];

    private final IMemoryTargetBridge.Stub binder = new IMemoryTargetBridge.Stub() {
        @Override
        public long getGeneration() {
            return MemoryRuntimeSession.currentGeneration();
        }

        @Override
        public int getTargetPid() {
            return Process.myPid();
        }

        @Override
        public long getProbeAddress() {
            return NativeTargetProbe.probeAddress();
        }

        @Override
        public long getProbeValue() {
            return NativeTargetProbe.probeValue();
        }

        @Override
        public int getPageSize() {
            return NativeTargetProbe.pageSize();
        }

        @Override
        public long[] getResidentJavaRuns(long generation, int scope, int maxRuns) {
            if (!MemoryRuntimeSession.isActive(generation) || maxRuns <= 0 || maxRuns > MAX_RUNS) {
                return EMPTY;
            }
            synchronized (runsLock) {
                int count = NativeTargetProbe.fillResidentJavaRuns(residentRuns, scope, maxRuns);
                if (count < 0) return EMPTY;
                return residentRuns;
            }
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
