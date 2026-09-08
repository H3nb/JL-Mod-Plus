/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.memory;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;

/** Minimal :midlet bridge for runtime identity and target-owned Managed Java memory operations. */
public final class MemoryTargetBridgeService extends Service {
	private final RemoteCallbackList<IMemoryTargetCallback> callbacks = new RemoteCallbackList<>();
	private final MemoryRuntimeSession.Listener runtimeListener = this::notifyRuntimeEnded;
	private final ManagedJavaMemoryEngine managedEngine = new ManagedJavaMemoryEngine();

	private final IMemoryTargetBridge.Stub binder = new IMemoryTargetBridge.Stub() {
		@Override
		public void registerTargetCallback(IMemoryTargetCallback callback) {
			if (callback != null) {
				callbacks.register(callback);
			}
		}

		@Override
		public void unregisterTargetCallback(IMemoryTargetCallback callback) {
			if (callback != null) {
				callbacks.unregister(callback);
			}
		}

		@Override
		public long getRuntimeToken() {
			return MemoryRuntimeSession.currentToken();
		}

		@Override
		public Bundle getManagedCapabilities(long runtimeToken) {
			return managedCapabilities(runtimeToken, managedEngine.capabilities(runtimeToken));
		}

		@Override
		public Bundle getManagedSessionInfo(long runtimeToken) {
			return managedSession(runtimeToken, managedEngine.session(runtimeToken));
		}

		@Override
		public Bundle managedStartExact(long runtimeToken, int valueType, int predicate,
				String firstValue, String secondValue, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.startExact(runtimeToken, valueType, predicate,
					firstValue, secondValue, cancellationEpoch));
		}

		@Override
		public Bundle managedStartUnknown(long runtimeToken, int valueType, long cancellationEpoch) {
			return managedResult(runtimeToken,
					managedEngine.startUnknown(runtimeToken, valueType, cancellationEpoch));
		}

		@Override
		public Bundle managedStartGroup(long runtimeToken, int valueType, String[] values,
				long cancellationEpoch) {
			return managedResult(runtimeToken,
					managedEngine.startGroup(runtimeToken, valueType, values, cancellationEpoch));
		}

		@Override
		public Bundle managedRefine(long runtimeToken, long expectedRevision, int valueType,
				int predicate, int compareTarget, String firstValue, String secondValue,
				long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.refine(runtimeToken, expectedRevision, valueType,
					predicate, compareTarget, firstValue, secondValue, cancellationEpoch));
		}

		@Override
		public Bundle managedStartExactInt(long runtimeToken, int value, long cancellationEpoch) {
			return managedResult(runtimeToken,
					managedEngine.startExactInt(runtimeToken, value, cancellationEpoch));
		}

		@Override
		public Bundle managedRefineInt(long runtimeToken, long expectedRevision, int predicate,
				int compareTarget, int value, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.refineInt(runtimeToken, expectedRevision, predicate,
					compareTarget, value, cancellationEpoch));
		}

		@Override
		public Bundle managedFilter(long runtimeToken, long expectedRevision, long[] ids,
				boolean keep, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.filter(runtimeToken, expectedRevision,
					ids, keep, cancellationEpoch));
		}

		@Override
		public Bundle managedUndo(long runtimeToken, long expectedRevision, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.undo(runtimeToken, expectedRevision,
					cancellationEpoch));
		}

		@Override
		public Bundle managedResultPage(long runtimeToken, long expectedRevision, int offset, int limit) {
			return resultPage(runtimeToken,
					managedEngine.resultPage(runtimeToken, expectedRevision, offset, limit));
		}

		@Override
		public Bundle managedWatchPage(long runtimeToken) {
			return watchPage(runtimeToken, managedEngine.watchPage(runtimeToken));
		}

		@Override
		public Bundle managedRefresh(long runtimeToken, long[] ids, long expectedRevision,
				long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.refresh(runtimeToken, ids, expectedRevision,
					cancellationEpoch));
		}

		@Override
		public Bundle managedEdit(long runtimeToken, long expectedRevision, long[] ids,
				int replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.edit(runtimeToken, expectedRevision, ids,
					replacement, allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedEditTyped(long runtimeToken, long expectedRevision, long[] ids,
				int declaredType, String replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.editTyped(runtimeToken, expectedRevision, ids,
				declaredType, replacement, allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedInspect(long runtimeToken, long expectedRevision, long candidateId,
				int radius, boolean allowWatchOnly) {
			return managedInspection(runtimeToken,
					managedEngine.inspect(runtimeToken, expectedRevision, candidateId, radius,
							allowWatchOnly));
		}

		@Override
		public Bundle managedEditInspector(long runtimeToken, long expectedRevision,
				long anchorCandidateId, boolean allowWatchOnly, int relativeOffset, int valueType,
				long expectedBits, String replacement, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.editInspector(runtimeToken,
				expectedRevision, anchorCandidateId, allowWatchOnly, relativeOffset, valueType,
				expectedBits, replacement, cancellationEpoch));
		}

		@Override
		public Bundle managedAddWatch(long runtimeToken, long expectedRevision, long[] ids,
				long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.addWatch(runtimeToken, expectedRevision, ids,
					cancellationEpoch));
		}

		@Override
		public Bundle managedRemoveWatch(long runtimeToken, long[] ids, long cancellationEpoch) {
			return managedResult(runtimeToken,
					managedEngine.removeWatch(runtimeToken, ids, cancellationEpoch));
		}

		@Override
		public Bundle managedSetWatchLabel(long runtimeToken, long candidateId, String label,
				long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.setWatchLabel(runtimeToken, candidateId, label,
					cancellationEpoch));
		}

		@Override
		public Bundle managedSetFreezeLock(long runtimeToken, long expectedRevision, long[] ids,
				int replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.setFreezeLock(runtimeToken, expectedRevision,
					ids, replacement, allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedSetFreezeLockTyped(long runtimeToken, long expectedRevision, long[] ids,
				String replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.setFreezeLockTyped(runtimeToken,
					expectedRevision, ids, replacement, allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedClearFreeze(long runtimeToken, long[] ids, long cancellationEpoch) {
			return managedResult(runtimeToken,
					managedEngine.clearFreeze(runtimeToken, ids, cancellationEpoch));
		}

		@Override
		public Bundle managedFreezeTick(long runtimeToken, long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.freezeTick(runtimeToken, cancellationEpoch));
		}

		@Override
		public Bundle managedClearSearch(long runtimeToken, long expectedRevision,
				long cancellationEpoch) {
			return managedResult(runtimeToken, managedEngine.clearSearchResult(runtimeToken,
					expectedRevision, cancellationEpoch));
		}

		@Override
		public void clearManagedSearch(long runtimeToken, long cancellationEpoch) {
			managedEngine.clearSearch(runtimeToken, cancellationEpoch);
		}

		@Override
		public void cancelManaged(long runtimeToken, long cancellationEpoch) {
			managedEngine.cancel(runtimeToken, cancellationEpoch);
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		MemoryRuntimeSession.addListener(runtimeListener);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		MemoryRuntimeSession.removeListener(runtimeListener);
		callbacks.kill();
		super.onDestroy();
	}

	private void notifyRuntimeEnded(long token) {
		managedEngine.runtimeClosed(token);
		int count = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < count; index++) {
				try {
					callbacks.getBroadcastItem(index).onRuntimeEnded(token);
				} catch (RemoteException ignored) {
					// RemoteCallbackList removes dead clients.
				}
			}
		} finally {
			callbacks.finishBroadcast();
		}
	}

	private static Bundle managedCapabilities(long runtimeToken,
	                                          ManagedJavaMemoryEngine.ManagedCapabilities state) {
		Bundle result = new Bundle();
		result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, runtimeToken);
		result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, state.supported);
		result.putBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, state.writeSupported);
		result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, state.supported);
		result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, state.writeSupported);
		result.putLong(MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH, state.controlEpoch);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, state.revision);
		result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT, state.resultCount);
		result.putLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT, state.baselineCount);
		result.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, state.historyDepth);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, state.watchCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, state.freezeCount);
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle managedSession(long runtimeToken,
	                                     ManagedJavaMemoryEngine.ManagedSession state) {
		Bundle result = new Bundle();
		result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, runtimeToken);
		result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, state.supported);
		result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, state.supported);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, state.revision);
		result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT, state.resultCount);
		result.putLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT, state.baselineCount);
		result.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, state.historyDepth);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, state.watchCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, state.freezeCount);
		result.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE, state.stage);
		result.putInt(MemoryEngineContract.KEY_SEARCH_MODE, state.mode);
		result.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE, state.requestedType);
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle managedResult(long runtimeToken,
	                                    ManagedJavaMemoryEngine.ManagedOperationResult state) {
		Bundle result = new Bundle();
		result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, runtimeToken);
		result.putInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT, state.code);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, state.revision);
		result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT, state.resultCount);
		result.putLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT, state.baselineCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_ATTEMPTED, state.attempted);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WRITTEN, state.written);
		result.putInt(MemoryEngineContract.KEY_MANAGED_SKIPPED, state.skipped);
		result.putInt(MemoryEngineContract.KEY_MANAGED_UNCONFIRMED, state.unconfirmed);
		result.putInt(MemoryEngineContract.KEY_MANAGED_NOT_ATTEMPTED, state.notAttempted);
		result.putInt(MemoryEngineContract.KEY_MANAGED_REJECTED_BEFORE_WRITE,
				state.rejectedBeforeWrite);
		result.putInt(MemoryEngineContract.KEY_MANAGED_SKIPPED_BY_TYPE, state.skippedByType);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, state.watchCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, state.freezeCount);
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle managedInspection(long runtimeToken,
	                                       ManagedJavaMemoryEngine.ManagedInspection state) {
		Bundle result = new Bundle();
		result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, runtimeToken);
		result.putInt(MemoryEngineContract.KEY_INSPECT_RESULT, state.code);
		if (state.code == MemoryEngineContract.RESULT_OK) {
			result.putLong(MemoryEngineContract.KEY_INSPECT_EXPECTED_REVISION, state.revision);
			result.putLongArray(MemoryEngineContract.KEY_INSPECT_IDS, state.ids);
			result.putStringArray(MemoryEngineContract.KEY_INSPECT_VALUES, state.values);
			result.putStringArray(MemoryEngineContract.KEY_INSPECT_INITIAL_VALUES,
					state.initialValues);
			result.putStringArray(MemoryEngineContract.KEY_INSPECT_PREVIOUS_VALUES,
					state.previousValues);
			result.putIntArray(MemoryEngineContract.KEY_INSPECT_TYPES, state.types);
			result.putIntArray(MemoryEngineContract.KEY_INSPECT_STATES, state.states);
			result.putIntArray(MemoryEngineContract.KEY_INSPECT_RELATIVE_OFFSETS,
					state.relativeOffsets);
			result.putLongArray(MemoryEngineContract.KEY_INSPECT_EXPECTED_BITS,
					state.expectedBits);
			result.putStringArray(MemoryEngineContract.KEY_INSPECT_LABELS, state.labels);
			result.putBooleanArray(MemoryEngineContract.KEY_INSPECT_EDITABLE, state.editable);
			result.putString(MemoryEngineContract.KEY_INSPECT_PROVENANCE, state.provenance);
		}
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle resultPage(long runtimeToken, ManagedJavaMemoryEngine.ManagedPage page) {
		Bundle result = new Bundle();
		result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, runtimeToken);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, page.revision);
		result.putLongArray(MemoryEngineContract.KEY_RESULT_IDS, page.ids);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_VALUES, page.values);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_ADDRESSES, page.addresses);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_ALIAS_MASKS, page.aliasMasks);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_TYPES, page.types);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_STATES, page.states);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_RELOCATIONS, page.relocations);
		return result;
	}

	private static Bundle watchPage(long runtimeToken, ManagedJavaMemoryEngine.ManagedPage page) {
		Bundle result = resultPage(runtimeToken, page);
		// A Watch page is presentation-only. Its revision field is deliberately zero and must
		// never be interpreted by :memory_engine as the current search revision.
		result.remove(MemoryEngineContract.KEY_MANAGED_REVISION);
		result.putLongArray(MemoryEngineContract.KEY_WATCH_IDS, page.ids);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_VALUES, page.values);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, page.initialValues);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, page.previousValues);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_ADDRESSES, page.addresses);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_TYPES, page.types);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_STATES, page.states);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_RELOCATIONS, page.relocations);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, page.labels);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, page.freezeModes);
		result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, page.freezePaused);
		return result;
	}
}
