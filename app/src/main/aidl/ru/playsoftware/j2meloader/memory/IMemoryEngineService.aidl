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

/**
 * Logical API. Result addresses are informational only: mutations never accept a raw destination,
 * and bounded Inspector/Nearby reads are anchored exclusively by a current CandidateId.
 */
interface IMemoryEngineService {
    Bundle getCapabilities();
    void registerCallback(IMemoryEngineCallback callback);
    void unregisterCallback(IMemoryEngineCallback callback);

    long startKnownSearch(long runtimeToken, int scope, int valueType, int predicate,
            String firstValue, String secondValue);
    long startUnknownSearch(long runtimeToken, int scope, int valueType);
    long startGroupSearch(long runtimeToken, int scope, in int[] valueTypes,
            in String[] values, int maxDistance);
    long startNearbySearch(long runtimeToken, long anchorCandidateId, int radius,
            int valueType, int predicate, String firstValue, String secondValue);
    long refineKnown(long runtimeToken, int predicate, String firstValue, String secondValue);
    long refineRelative(long runtimeToken, int predicate, int compareTarget,
            String firstValue, String secondValue);
    long undoSearch(long runtimeToken);
    long refreshCandidates(long runtimeToken, in long[] candidateIds, boolean passiveRefresh);
    long removeCandidates(long runtimeToken, in long[] candidateIds);
    long keepCandidates(long runtimeToken, in long[] candidateIds);
    long editCandidates(long runtimeToken, in long[] candidateIds, String replacementValue);
    long filterResultGroups(long runtimeToken, in long[] resultIds, boolean keep);
    long editResultGroups(long runtimeToken, in long[] resultIds, int valueType,
            String replacementValue);
    long addWatchResultGroups(long runtimeToken, in long[] resultIds, int valueType);
    long setFreezeResultGroups(long runtimeToken, in long[] resultIds, int valueType, int mode,
            String firstValue, String secondValue);
    long editInspectorValue(long runtimeToken, long anchorCandidateId, int relativeOffset,
            int valueType, long expectedBits, String replacementValue);

    long getResultCount(long runtimeToken);
    Bundle getSearchSessionInfo(long runtimeToken);
    // Offset/limit count unique raw addresses. The engine formats one presentation row per address.
    Bundle getResultPage(long runtimeToken, int offset, int limit);
    Bundle inspectCandidate(long runtimeToken, long candidateId, int radius);
    Bundle getWatchPage(long runtimeToken);
    long addWatch(long runtimeToken, in long[] candidateIds);
    long removeWatch(long runtimeToken, in long[] candidateIds);
    long setWatchLabel(long runtimeToken, long candidateId, String label);
    long setFreeze(long runtimeToken, in long[] candidateIds, int mode,
            String firstValue, String secondValue);
    long clearFreeze(long runtimeToken, in long[] candidateIds);
    void clearSearch(long runtimeToken);
    void cancelOperation(long runtimeToken);
}
