/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package ru.playsoftware.j2meloader.memory;

import android.os.Bundle;
import ru.playsoftware.j2meloader.memory.IMemoryEngineCallback;

/** Logical API; result addresses are informational and no operation accepts a raw destination. */
interface IMemoryEngineService {
    Bundle getCapabilities();
    void registerCallback(IMemoryEngineCallback callback);
    void unregisterCallback(IMemoryEngineCallback callback);

    long startKnownSearch(long runtimeToken, int scope, int valueType, int predicate,
            String firstValue, String secondValue);
    long startUnknownSearch(long runtimeToken, int scope, int valueType);
    long refineKnown(long runtimeToken, int predicate, String firstValue, String secondValue);
    long refineRelative(long runtimeToken, int predicate, int compareTarget,
            String firstValue, String secondValue);
    long undoSearch(long runtimeToken);

    long getResultCount(long runtimeToken);
    long[] getResultPage(long runtimeToken, int offset, int limit);
    void clearSearch(long runtimeToken);
    void cancelOperation(long runtimeToken);
}
