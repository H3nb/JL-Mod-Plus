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
import android.os.Debug;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;

/** Minimal :midlet bridge for runtime identity and target-local mincore collection. */
public final class MemoryTargetBridgeService extends Service {
	private static final String ART_GC_COUNT_STAT = "art.gc.gc-count";
	private static final long[] EMPTY_RUNS = new long[]{0L, 0L};
	private final Object rangeLock = new Object();
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
		public int getTargetPid() {
			return Process.myPid();
		}

		@Override
		public int getPageSize() {
			return NativeMemoryTarget.pageSize();
		}

		@Override
		public long getGcCount(long runtimeToken) {
			return MemoryRuntimeSession.isActive(runtimeToken)
					? readGcCount() : MemoryEngineContract.GC_COUNT_UNKNOWN;
		}

		@Override
		public long[] getReadProbe(long runtimeToken) {
			if (!MemoryRuntimeSession.isActive(runtimeToken)) {
				return new long[0];
			}
			long[] probe = NativeMemoryTarget.readProbe();
			return probe == null ? new long[0] : probe;
		}

		@Override
		public long[] getResidentRuns(long runtimeToken, int scope, int maxRuns) {
			if (!MemoryRuntimeSession.isActive(runtimeToken)
					|| !MemoryEngineContract.isRawScope(scope)
					|| maxRuns <= 0 || maxRuns > MemoryEngineContract.MAX_RESIDENT_RUNS) {
				return EMPTY_RUNS;
			}
			synchronized (rangeLock) {
				long[] runs = NativeMemoryTarget.collectResidentRuns(scope, maxRuns);
				return runs == null ? EMPTY_RUNS : runs;
			}
		}

		@Override
		public Bundle getManagedCapabilities(long runtimeToken) {
			return managedCapabilities(managedEngine.capabilities(runtimeToken));
		}

		@Override
		public Bundle getManagedSessionInfo(long runtimeToken) {
			return managedSession(managedEngine.session(runtimeToken));
		}

		@Override
		public Bundle managedStartExact(long runtimeToken, int valueType, int predicate,
				String firstValue, String secondValue, long cancellationEpoch) {
			return managedResult(managedEngine.startExact(runtimeToken, valueType, predicate,
					firstValue, secondValue, cancellationEpoch));
		}

		@Override
		public Bundle managedRefine(long runtimeToken, long expectedRevision, int valueType,
				int predicate, int compareTarget, String firstValue, String secondValue,
				long cancellationEpoch) {
			return managedResult(managedEngine.refine(runtimeToken, expectedRevision, valueType,
					predicate, compareTarget, firstValue, secondValue, cancellationEpoch));
		}

		@Override
		public Bundle managedStartExactInt(long runtimeToken, int value, long cancellationEpoch) {
			return managedResult(managedEngine.startExactInt(runtimeToken, value, cancellationEpoch));
		}

		@Override
		public Bundle managedRefineInt(long runtimeToken, long expectedRevision, int predicate,
				int compareTarget, int value, long cancellationEpoch) {
			return managedResult(managedEngine.refineInt(runtimeToken, expectedRevision, predicate,
					compareTarget, value, cancellationEpoch));
		}

		@Override
		public Bundle managedResultPage(long runtimeToken, long expectedRevision, int offset, int limit) {
			return resultPage(managedEngine.resultPage(runtimeToken, expectedRevision, offset, limit));
		}

		@Override
		public Bundle managedWatchPage(long runtimeToken) {
			return watchPage(managedEngine.watchPage(runtimeToken));
		}

		@Override
		public Bundle managedRefresh(long runtimeToken, long[] ids, long expectedRevision,
				long cancellationEpoch) {
			return managedResult(managedEngine.refresh(runtimeToken, ids, expectedRevision,
					cancellationEpoch));
		}

		@Override
		public Bundle managedEdit(long runtimeToken, long expectedRevision, long[] ids,
				int replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(managedEngine.edit(runtimeToken, expectedRevision, ids, replacement,
					allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedEditTyped(long runtimeToken, long expectedRevision, long[] ids,
				String replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(managedEngine.editTyped(runtimeToken, expectedRevision, ids, replacement,
					allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedAddWatch(long runtimeToken, long expectedRevision, long[] ids,
				long cancellationEpoch) {
			return managedResult(managedEngine.addWatch(runtimeToken, expectedRevision, ids,
					cancellationEpoch));
		}

		@Override
		public Bundle managedRemoveWatch(long runtimeToken, long[] ids, long cancellationEpoch) {
			return managedResult(managedEngine.removeWatch(runtimeToken, ids, cancellationEpoch));
		}

		@Override
		public Bundle managedSetWatchLabel(long runtimeToken, long candidateId, String label,
				long cancellationEpoch) {
			return managedResult(managedEngine.setWatchLabel(runtimeToken, candidateId, label,
					cancellationEpoch));
		}

		@Override
		public Bundle managedSetFreezeLock(long runtimeToken, long expectedRevision, long[] ids,
				int replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(managedEngine.setFreezeLock(runtimeToken, expectedRevision, ids,
					replacement, allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedSetFreezeLockTyped(long runtimeToken, long expectedRevision, long[] ids,
				String replacement, boolean allowWatchOnly, long cancellationEpoch) {
			return managedResult(managedEngine.setFreezeLockTyped(runtimeToken, expectedRevision, ids,
					replacement, allowWatchOnly, cancellationEpoch));
		}

		@Override
		public Bundle managedClearFreeze(long runtimeToken, long[] ids, long cancellationEpoch) {
			return managedResult(managedEngine.clearFreeze(runtimeToken, ids, cancellationEpoch));
		}

		@Override
		public Bundle managedFreezeTick(long runtimeToken, long cancellationEpoch) {
			return managedResult(managedEngine.freezeTick(runtimeToken, cancellationEpoch));
		}

		@Override
		public Bundle managedClearSearch(long runtimeToken, long expectedRevision,
				long cancellationEpoch) {
			return managedResult(managedEngine.clearSearchResult(runtimeToken, expectedRevision,
					cancellationEpoch));
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

	static long readGcCount() {
		try {
			return parseGcCount(Debug.getRuntimeStat(ART_GC_COUNT_STAT));
		} catch (RuntimeException exception) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
	}

	static long parseGcCount(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
		try {
			long count = Long.parseLong(value);
			return count >= 0L ? count : MemoryEngineContract.GC_COUNT_UNKNOWN;
		} catch (NumberFormatException exception) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
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

	private static Bundle managedCapabilities(ManagedJavaMemoryEngine.ManagedCapabilities state) {
		Bundle result = new Bundle();
		result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, state.supported);
		result.putBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, state.writeSupported);
		result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, state.supported);
		result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, state.writeSupported);
		result.putLong(MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH, state.controlEpoch);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, state.revision);
		result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT, state.resultCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, state.watchCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, state.freezeCount);
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle managedSession(ManagedJavaMemoryEngine.ManagedSession state) {
		Bundle result = new Bundle();
		result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, state.supported);
		result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, state.supported);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, state.revision);
		result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT, state.resultCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, state.watchCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, state.freezeCount);
		result.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE, state.stage);
		result.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE, state.requestedType);
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle managedResult(ManagedJavaMemoryEngine.ManagedOperationResult state) {
		Bundle result = new Bundle();
		result.putInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT, state.code);
		result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, state.revision);
		result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT, state.resultCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_ATTEMPTED, state.attempted);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WRITTEN, state.written);
		result.putInt(MemoryEngineContract.KEY_MANAGED_SKIPPED, state.skipped);
		result.putInt(MemoryEngineContract.KEY_MANAGED_UNCONFIRMED, state.unconfirmed);
		result.putInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, state.watchCount);
		result.putInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, state.freezeCount);
		if (state.message != null && !state.message.isBlank()) {
			result.putString(MemoryEngineContract.KEY_MESSAGE, state.message);
		}
		return result;
	}

	private static Bundle resultPage(ManagedJavaMemoryEngine.ManagedPage page) {
		Bundle result = new Bundle();
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

	private static Bundle watchPage(ManagedJavaMemoryEngine.ManagedPage page) {
		Bundle result = resultPage(page);
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
		result.putIntArray(MemoryEngineContract.KEY_WATCH_BACKENDS, page.backends);
		return result;
	}
}
