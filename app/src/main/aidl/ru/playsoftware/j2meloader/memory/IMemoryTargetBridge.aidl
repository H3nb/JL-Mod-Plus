/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

package ru.playsoftware.j2meloader.memory;

import android.os.Bundle;
import ru.playsoftware.j2meloader.memory.IMemoryTargetCallback;

/** Thin bridge to the target :midlet process; scanning and candidate ownership stay target-owned. */
interface IMemoryTargetBridge {
    void registerTargetCallback(IMemoryTargetCallback callback);
    void unregisterTargetCallback(IMemoryTargetCallback callback);
    long getRuntimeToken();

    /** Managed Java graph backend; all values and ids remain logical and target-owned. */
    Bundle getManagedCapabilities(long runtimeToken);
    Bundle getManagedSessionInfo(long runtimeToken);
    Bundle managedStartExact(long runtimeToken, int valueType, int predicate,
            String firstValue, String secondValue, long cancellationEpoch);
    Bundle managedStartUnknown(long runtimeToken, int valueType, long cancellationEpoch);
    Bundle managedStartGroup(long runtimeToken, int valueType, in String[] values,
            long cancellationEpoch);
    Bundle managedRefine(long runtimeToken, long expectedRevision, int valueType, int predicate,
            int compareTarget, String firstValue, String secondValue, long cancellationEpoch);
    Bundle managedFilter(long runtimeToken, long expectedRevision, in long[] ids,
            boolean keep, long cancellationEpoch);
    Bundle managedUndo(long runtimeToken, long expectedRevision, long cancellationEpoch);
    Bundle managedResultPage(long runtimeToken, long expectedRevision, int offset, int limit);
    Bundle managedWatchPage(long runtimeToken);
    Bundle managedRefresh(long runtimeToken, in long[] ids, long expectedRevision,
            long cancellationEpoch);
	Bundle managedEdit(long runtimeToken, long expectedRevision, in long[] ids,
            int replacement, boolean allowWatchOnly, long cancellationEpoch);
	Bundle managedEditTyped(long runtimeToken, long expectedRevision, in long[] ids,
			int declaredType, String replacement, boolean allowWatchOnly, long cancellationEpoch);
    Bundle managedInspect(long runtimeToken, long expectedRevision, long candidateId, int radius,
            boolean allowWatchOnly);
    Bundle managedEditInspector(long runtimeToken, long expectedRevision, long anchorCandidateId,
            boolean allowWatchOnly, int relativeOffset, int valueType, long expectedBits,
            String replacement, long cancellationEpoch);
    Bundle managedAddWatch(long runtimeToken, long expectedRevision, in long[] ids,
            long cancellationEpoch);
    Bundle managedRemoveWatch(long runtimeToken, in long[] ids, long cancellationEpoch);
    Bundle managedSetWatchLabel(long runtimeToken, long candidateId, String label,
            long cancellationEpoch);
	Bundle managedSetFreezeLock(long runtimeToken, long expectedRevision, in long[] ids,
            int replacement, boolean allowWatchOnly, long cancellationEpoch);
	Bundle managedSetFreezeLockTyped(long runtimeToken, long expectedRevision, in long[] ids,
            String replacement, boolean allowWatchOnly, long cancellationEpoch);
    Bundle managedClearFreeze(long runtimeToken, in long[] ids, long cancellationEpoch);
    Bundle managedFreezeTick(long runtimeToken, long cancellationEpoch);
    Bundle managedClearSearch(long runtimeToken, long expectedRevision, long cancellationEpoch);
    void cancelManaged(long runtimeToken, long cancellationEpoch);
}
