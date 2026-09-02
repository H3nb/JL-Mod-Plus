/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package ru.playsoftware.j2meloader.memory;

import ru.playsoftware.j2meloader.memory.IMemoryTargetCallback;

/** Thin target-process bridge. Scanning and candidate ownership remain in :memory_engine. */
interface IMemoryTargetBridge {
    void registerTargetCallback(IMemoryTargetCallback callback);
    void unregisterTargetCallback(IMemoryTargetCallback callback);
    long getRuntimeToken();
    int getTargetPid();
    int getPageSize();

    /**
     * Returns the current ART garbage-collection count for this runtime, or
     * MemoryEngineContract.GC_COUNT_UNKNOWN when the runtime statistic is unavailable.
     * The count is a relocation signal, not proof that any particular object moved.
     */
    long getGcCount(long runtimeToken);

    /** Returns [address, expectedBits] for a target-owned read-only capability probe. */
    long[] getReadProbe(long runtimeToken);

    /**
     * Returns [runCount, truncated, start0, end0, ...]. Only resident readable/writable runs
     * selected by the requested scope are returned.
     */
    long[] getResidentRuns(long runtimeToken, int scope, int maxRuns);
}
