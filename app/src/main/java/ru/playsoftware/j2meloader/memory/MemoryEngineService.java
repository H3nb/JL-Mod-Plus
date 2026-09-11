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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates the editor process and exposes target-owned Managed Java state as logical IDs. */
public final class MemoryEngineService extends Service {
	public interface LocalRuntimeListener {
		void onTargetUnavailable();
	}

	private static final Set<LocalRuntimeListener> LOCAL_RUNTIME_LISTENERS =
			Collections.newSetFromMap(new ConcurrentHashMap<LocalRuntimeListener, Boolean>());
	// This generation distinguishes a newly started operation from a cancellation delivered by
	// Binder immediately before its Managed entry point.
	private static final AtomicLong cancelEpoch = new AtomicLong();

	private final AtomicLong nextOperationId = new AtomicLong(1L);
	private final RemoteCallbackList<IMemoryEngineCallback> callbacks = new RemoteCallbackList<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "MemoryEditorEngine");
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	private final ScheduledExecutorService freezeScheduler =
			Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "MemoryEditorFreeze");
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			});
	private volatile IMemoryTargetBridge target;
	private volatile boolean targetBound;
	private volatile long configuredToken;
	private volatile long observedRuntimeToken;
	private volatile boolean managedSupported;
	private volatile boolean managedWriteSupported;
	private volatile long managedStateToken;
	private volatile long managedRevision;
	private volatile long managedResultCount;
	private volatile long managedBaselineCount;
	private volatile int managedHistoryDepth;
	private volatile int managedWatchCount;
	private volatile int managedFreezeCount;
	private volatile String managedLastMessage;
	private final AtomicLong searchClearGeneration = new AtomicLong();
	private volatile ScheduledFuture<?> freezeTask;
	/** Serializes only the tiny local Managed-publication boundary. */
	private final Object searchCommitLock = new Object();
	private final Object searchSessionLock = new Object();
	private int searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
	private int searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
	private int searchRequestedType = MemoryEngineContract.TYPE_AUTO;
	private final IMemoryTargetCallback targetCallback = new IMemoryTargetCallback.Stub() {
		@Override
		public void onRuntimeEnded(long runtimeToken) {
			if (runtimeToken != 0L &&
					(runtimeToken == configuredToken || runtimeToken == observedRuntimeToken)) {
				invalidateTarget();
			}
		}
	};

	private final ServiceConnection targetConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			IMemoryTargetBridge bridge = IMemoryTargetBridge.Stub.asInterface(service);
			target = bridge;
			try {
				bridge.registerTargetCallback(targetCallback);
				scheduleTargetStateRehydrate(bridge);
			} catch (RemoteException exception) {
				target = null;
				invalidateTarget();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			target = null;
			invalidateTarget();
		}

		@Override
		public void onBindingDied(ComponentName name) {
			target = null;
			invalidateTarget();
		}
	};

	private final IMemoryEngineService.Stub binder = new IMemoryEngineService.Stub() {
		@Override
		public Bundle getCapabilities() {
			Bundle result = new Bundle();
			IMemoryTargetBridge bridge = target;
			if (bridge == null) {
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, false);
				result.putString(MemoryEngineContract.KEY_MESSAGE, "MIDlet runtime is not connected");
				return result;
			}
			try {
				long token = bridge.getRuntimeToken();
				observedRuntimeToken = token;
				Bundle managed = token == 0L ? null : bridge.getManagedCapabilities(token);
				if (managed != null && acceptManagedRuntimeState(token, bridge, managed)) {
					updateManagedState(token, managed);
				} else {
					managed = null;
				}
				boolean managedAvailable = token != 0L && managedSupported;
				boolean managedWriteAvailable = token != 0L && managedWriteSupported;
				if (token != 0L && configuredToken == 0L && managedAvailable
						&& (managedRevision > 0L || managedWatchCount > 0 || managedFreezeCount > 0)) {
					// A freshly reconnected :memory_engine process may have no local session metadata.
					// Adopt only target-owned Managed state; a target with Watch entries but no
					// search remains an empty search session.
					configuredToken = token;
				}
				reconcileFreezeScheduler();
				// Managed is the only production backend. Do not probe the target process while
				// publishing ordinary capabilities.
				boolean supported = managedAvailable;
				boolean writeSupported = managedWriteAvailable;
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, supported);
				result.putBoolean(MemoryEngineContract.KEY_WRITE_SUPPORTED, writeSupported);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, managedAvailable);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED,
						managedWriteAvailable);
				long visibleManagedRevision = token != 0L && configuredToken == token
						? managedRevision : 0L;
				long visibleManagedResultCount = token != 0L && configuredToken == token
						? managedResultCount : 0L;
				result.putLong(MemoryEngineContract.KEY_MANAGED_REVISION, visibleManagedRevision);
				result.putLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT,
						visibleManagedResultCount);
				result.putLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT,
						token != 0L && configuredToken == token
						? managedBaselineCount : 0L);
				result.putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, token);
				if (!supported) {
					result.putString(MemoryEngineContract.KEY_MESSAGE, token == 0L
							? "No active MIDlet runtime"
							: (managed == null ? "Managed Java discovery is unavailable"
							: "Managed Java memory editing is unavailable"));
				}
			} catch (RemoteException exception) {
				result.putBoolean(MemoryEngineContract.KEY_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED, false);
				result.putBoolean(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED, false);
				result.putString(MemoryEngineContract.KEY_MESSAGE, "MIDlet runtime connection was lost");
			}
			return result;
		}

		@Override
		public void registerCallback(IMemoryEngineCallback callback) {
			if (callback != null) {
				callbacks.register(callback);
			}
		}

		@Override
		public void unregisterCallback(IMemoryEngineCallback callback) {
			if (callback != null) {
				callbacks.unregister(callback);
			}
		}

		@Override
		public long startKnownSearch(long token, int type, int predicate,
		                             String first, String second) {
			return enqueueManagedSearch(token, () -> managedStartExact(token, type, predicate,
					first, second));
		}

		@Override
		public long startUnknownSearch(long token, int type) {
			return enqueueManagedSearch(token, () -> managedStartUnknown(token, type));
		}

		@Override
		public long startGroupSearch(long token, int type, String[] values) {
			if (!MemoryEngineContract.isCandidateType(type) || values == null
					|| values.length < 2 || values.length > MemoryEngineContract.MAX_GROUP_VALUES) {
				return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_INVALID_REQUEST);
			}
			return enqueueManagedSearch(token, () -> managedStartGroup(token, type, values));
		}

		@Override
		public long refineKnown(long token, int valueType, int predicate, String first, String second) {
			if (isManagedCurrent(token)) {
				long expectedRevision = managedRevision;
				return enqueueManagedSearch(token, () -> managedRefine(token, expectedRevision,
						valueType, predicate, MemoryEngineContract.COMPARE_PREVIOUS, first, second));
			}
			return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_NO_SESSION);
		}

		@Override
		public long refineRelative(long token, int valueType, int predicate, int compareTarget,
		                           String first, String second) {
			if (isManagedCurrent(token)) {
				long expectedRevision = managedRevision;
				return enqueueManagedSearch(token, () -> managedRefine(token, expectedRevision,
						valueType, predicate, compareTarget, first, second));
			}
			return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_NO_SESSION);
		}

		@Override
		public long undoSearch(long token) {
			if (isManagedCurrent(token)) {
				long expectedRevision = managedRevision;
				return enqueueManagedSearch(token, () -> managedUndo(token, expectedRevision));
			}
			return enqueueManagedSearch(token, () -> MemoryEngineContract.RESULT_NO_SESSION);
		}

		@Override
		public long refreshCandidates(long token, long[] candidateIds, boolean passiveRefresh) {
			long[] ids = candidateIds == null ? new long[0] : candidateIds;
			if (containsManagedIds(ids)) {
				if (!allManagedIds(ids)) return enqueueManaged(token, passiveRefresh,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false, () -> managedRefresh(token, ids, passiveRefresh));
			}
			return enqueueManaged(token, passiveRefresh,
					() -> MemoryEngineContract.RESULT_IDENTITY_UNSAFE);
		}

		@Override
		public long editCandidates(long token, long[] candidateIds, int valueType,
		                           String replacementValue) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false, () -> managedEdit(token,
						isManagedCurrent(token) ? managedRevision : 0L, candidateIds,
						valueType, replacementValue, true));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public long getResultCount(long token) {
			if (isManagedCurrent(token)) return managedResultCount;
			return 0L;
		}

		@Override
		public Bundle getSearchSessionInfo(long token) {
			return searchSessionInfo(token);
		}

		@Override
		public Bundle getResultPage(long token, int offset, int limit) {
			if (!isCurrentToken(token) || offset < 0 || limit <= 0 ||
					limit > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
				return emptyResultPage();
			}
			if (isManagedCurrent(token)) {
				return managedResultPage(token, managedRevision, offset, limit);
			}
			return emptyResultPage();
		}

		@Override
		public long filterResultGroups(long token, long expectedRevision, long[] resultIds, boolean keep) {
			if (resultIds == null || resultIds.length == 0
					|| resultIds.length > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
				return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_SAFETY_LIMIT);
			}
			if (containsManagedIds(resultIds)) {
				if (!allManagedIds(resultIds)) {
					return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				}
				return enqueueManaged(token, false,
						() -> managedFilterResultGroups(token, expectedRevision, resultIds, keep));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_IDENTITY_UNSAFE);
		}

		@Override
		public long editResults(long token, long expectedRevision, long[] resultIds,
		                                    int valueType, String replacementValue) {
			return enqueueManaged(token, false, () -> managedEdit(token, expectedRevision,
					resultIds, valueType, replacementValue, false));
		}

		@Override
		public long addWatchResults(long token, long expectedRevision, long[] resultIds) {
			return enqueueManaged(token, false, () -> managedAddWatch(token, expectedRevision,
					resultIds));
		}

		@Override
		public long setFreezeResults(long token, long expectedRevision, long[] resultIds,
		                                        int mode, String firstValue, String secondValue) {
			return enqueueManaged(token, false, () -> managedFreeze(token, expectedRevision,
					resultIds, mode, firstValue, secondValue, false));
		}

		@Override
		public long editInspectorValue(long token, long anchorCandidateId, int relativeOffset,
		                               int valueType, long expectedBits,
		                               String replacementValue, boolean watchAnchor, long expectedRevision) {
			if (ManagedJavaMemoryIds.isManaged(anchorCandidateId)) {
				return enqueueManaged(token, false, () -> managedEditInspector(token,
						anchorCandidateId, watchAnchor, expectedRevision, relativeOffset, valueType, expectedBits,
						replacementValue));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public Bundle inspectCandidate(long token, long candidateId, int radius, boolean watchAnchor) {
			if (ManagedJavaMemoryIds.isManaged(candidateId)) {
				return inspectManagedCandidate(token, candidateId, radius, watchAnchor);
			}
			return inspectionFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Inspector requires a Managed logical candidate");
		}

		@Override
		public Bundle getWatchPage(long token) {
			if (!isCurrentToken(token)) {
				return emptyWatchPage();
			}
			return managedWatchPage(token);
		}

		@Override
		public long addWatch(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false, () -> managedAddWatch(token, managedRevision,
						candidateIds));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public long removeWatch(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false, () -> managedRemoveWatch(token, candidateIds));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public long setWatchLabel(long token, long candidateId, String label) {
			if (ManagedJavaMemoryIds.isManaged(candidateId)) {
				return enqueueManaged(token, false, () -> managedSetWatchLabel(token, candidateId, label));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public long setFreeze(long token, long[] candidateIds, int mode,
		                      String firstValue, String secondValue) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false, () -> managedFreeze(token,
						isManagedCurrent(token) ? managedRevision : 0L, candidateIds, mode,
						firstValue, secondValue, true));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public long clearFreeze(long token, long[] candidateIds) {
			if (containsManagedIds(candidateIds)) {
				if (!allManagedIds(candidateIds)) return enqueueManaged(token, false,
						() -> MemoryEngineContract.RESULT_INVALID_REQUEST);
				return enqueueManaged(token, false, () -> managedClearFreeze(token, candidateIds));
			}
			return enqueueManaged(token, false, () -> MemoryEngineContract.RESULT_UNSUPPORTED);
		}

		@Override
		public void clearSearch(long token) {
			if (token == 0L || !isTargetToken(token)) return;
			long epoch;
			IMemoryTargetBridge bridge;
			synchronized (searchCommitLock) {
				clearSearchSession();
				epoch = cancelEpoch.incrementAndGet();
				searchClearGeneration.incrementAndGet();
					managedRevision = 0L;
				managedResultCount = 0L;
				managedBaselineCount = 0L;
				bridge = target;
			}
			if (bridge != null) {
				try {
					bridge.managedClearSearch(token, 0L, epoch);
				} catch (RemoteException ignored) {
					// Runtime teardown will clear target state.
				}
			}
			// Managed clear is target-owned and already committed synchronously through Binder.
		}

		@Override
		public void cancelOperation(long token) {
			if (token == 0L || (token != configuredToken && !isTargetToken(token))) return;
			synchronizeManagedCancelEpoch(token);
			long epoch = cancelEpoch.incrementAndGet();
			IMemoryTargetBridge bridge = target;
			if (bridge != null) {
				try {
					bridge.cancelManaged(token, epoch);
				} catch (RemoteException ignored) {
					// Runtime teardown handles cancellation.
				}
			}
		}
	};

	static void addLocalRuntimeListener(LocalRuntimeListener listener) {
		if (listener != null) LOCAL_RUNTIME_LISTENERS.add(listener);
	}

	static void removeLocalRuntimeListener(LocalRuntimeListener listener) {
		if (listener != null) LOCAL_RUNTIME_LISTENERS.remove(listener);
	}

	private static void notifyLocalRuntimeUnavailable() {
		for (LocalRuntimeListener listener : LOCAL_RUNTIME_LISTENERS) {
			try {
				listener.onTargetUnavailable();
			} catch (RuntimeException ignored) {
				// Activity teardown must not interrupt engine cleanup.
			}
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Intent intent = new Intent(this, MemoryTargetBridgeService.class);
		targetBound = bindService(intent, targetConnection, Context.BIND_AUTO_CREATE);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		stopFreezeTask();
		freezeScheduler.shutdownNow();
		IMemoryTargetBridge bridge = target;
		if (bridge != null) {
			try {
				bridge.unregisterTargetCallback(targetCallback);
			} catch (RemoteException ignored) {
				// The target may already be gone.
			}
		}
		cancelEpoch.incrementAndGet();
		worker.shutdownNow();
		callbacks.kill();
		LOCAL_RUNTIME_LISTENERS.clear();
		if (targetBound) {
			unbindService(targetConnection);
			targetBound = false;
		}
		super.onDestroy();
	}

	private long enqueueManagedSearch(long token, Operation operation) {
		return enqueueManaged(token, true, operation);
	}

	private long enqueueManaged(long token, boolean searchOperation, Operation operation) {
		long operationId = nextOperationId.getAndIncrement();
		long enqueueEpoch = cancelEpoch.get();
		worker.execute(() -> {
			int result;
			String serviceMessage = null;
			if (enqueueEpoch != cancelEpoch.get()) {
				result = MemoryEngineContract.RESULT_CANCELLED;
				serviceMessage = "Operation cancelled before it started";
			} else if (token == 0L) {
				result = MemoryEngineContract.RESULT_NO_SESSION;
				serviceMessage = "No active MIDlet runtime";
			} else if (!prepareManagedOperation(token) || !isTargetToken(token)) {
				result = MemoryEngineContract.RESULT_TARGET_LOST;
				serviceMessage = "MIDlet runtime changed or ended";
			} else {
				result = operation.run();
			}
			if (result == MemoryEngineContract.RESULT_NO_SESSION &&
					enqueueEpoch != cancelEpoch.get()) {
				result = MemoryEngineContract.RESULT_CANCELLED;
				serviceMessage = "Operation cancelled before it started";
			}
			if (result == MemoryEngineContract.RESULT_OK && !isTargetToken(token)) {
				configuredToken = 0L;
				result = MemoryEngineContract.RESULT_TARGET_LOST;
				serviceMessage = "MIDlet runtime changed during the operation";
			}
			notifyFinished(operationId, token, result, serviceMessage, false,
					searchOperation);
		});
		return operationId;
	}

	private boolean prepareManagedOperation(long token) {
		return isTargetToken(token) && synchronizeManagedCancelEpoch(token);
	}

	private int managedStartUnknown(long token, int type) {
		if (!MemoryEngineContract.isValueType(type)) {
			return managedFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Unknown search received an unsupported type selector");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = searchClearGeneration.get();
		try {
			Bundle result = bridge.managedStartUnknown(token, type, operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, 0L);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				clearSearchSession();
				configuredToken = token;
				resetSearchSession(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
						MemoryEngineContract.SEARCH_MODE_UNKNOWN, type);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private void scheduleTargetStateRehydrate(IMemoryTargetBridge bridge) {
		try {
			worker.execute(() -> rehydrateTargetState(bridge));
		} catch (RejectedExecutionException ignored) {
			// The service is already tearing down.
		}
	}

	private void rehydrateTargetState(IMemoryTargetBridge bridge) {
		try {
			long token = bridge.getRuntimeToken();
			if (token == 0L || target != bridge) return;
			Bundle capabilities = bridge.getManagedCapabilities(token);
			Bundle session = bridge.getManagedSessionInfo(token);
			if (!acceptManagedRuntimeState(token, bridge, capabilities)
					|| !acceptManagedRuntimeState(token, bridge, session)) {
				return;
			}
			synchronized (searchCommitLock) {
				if (target != bridge || bridge.getRuntimeToken() != token) return;
				observedRuntimeToken = token;
				if (configuredToken == 0L && hasRetainedManagedState(capabilities)) {
					configuredToken = token;
				}
				updateManagedState(token, capabilities);
				updateManagedState(token, session);
				reconcileFreezeScheduler();
			}
		} catch (RemoteException ignored) {
			// The next target connection or capability request will retry reconciliation.
		}
	}

	private static boolean hasRetainedManagedState(@Nullable Bundle state) {
		return state != null && (state.getLong(MemoryEngineContract.KEY_MANAGED_REVISION, 0L) > 0L
				|| state.getInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT, 0) > 0
				|| state.getInt(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, 0) > 0);
	}

	private int managedFilterResultGroups(long token, long expectedRevision, long[] resultIds,
	                                     boolean keep) {
		if (resultIds == null || resultIds.length == 0
				|| resultIds.length > MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
			return managedFailure(MemoryEngineContract.RESULT_SAFETY_LIMIT,
					"Managed candidate filtering is limited to 100 visible result rows");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = searchClearGeneration.get();
		try {
			Bundle result = bridge.managedFilter(token, expectedRevision, resultIds, keep,
					operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedUndo(long token, long expectedRevision) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = searchClearGeneration.get();
		try {
			Bundle result = bridge.managedUndo(token, expectedRevision, operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				reconcileManagedSession(token, bridge);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private void reconcileManagedSession(long token, IMemoryTargetBridge bridge) {
		try {
			Bundle state = bridge.getManagedSessionInfo(token);
			if (!acceptManagedRuntimeState(token, bridge, state)) return;
			updateManagedState(token, state);
		} catch (RemoteException ignored) {
			// The operation result remains authoritative; the next successful session read
			// completes metadata reconciliation.
		}
	}

	private int managedStartExact(long token, int type, int predicate, String first, String second) {
		if (!MemoryEngineContract.isValueType(type)) {
			return managedFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed Java supports Byte, Short, Char, Int, Long, Float, and Double");
		}
		if (predicate < MemoryEngineContract.PREDICATE_EQUAL
				|| predicate > MemoryEngineContract.PREDICATE_BETWEEN) {
			return managedFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Known search received an unsupported predicate");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = searchClearGeneration.get();
		try {
			Bundle result = bridge.managedStartExact(token, type, predicate, first, second,
					operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, 0L);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				clearSearchSession();
				configuredToken = token;
				resetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
						MemoryEngineContract.SEARCH_MODE_KNOWN, type);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedStartGroup(long token, int type, String[] values) {
		if (!MemoryEngineContract.isCandidateType(type) || values == null
				|| values.length < 2 || values.length > MemoryEngineContract.MAX_GROUP_VALUES) {
			return managedFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Managed Group search requires one concrete primitive type and 2 to 8 values");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = searchClearGeneration.get();
		try {
			Bundle result = bridge.managedStartGroup(token, type, values, operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, 0L);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
				clearSearchSession();
				configuredToken = token;
				resetSearchSession(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
						MemoryEngineContract.SEARCH_MODE_GROUP, type);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedRefine(long token, int predicate, int compareTarget, String value) {
		return managedRefine(token, managedRevision, managedSearchType(), predicate,
				compareTarget, value, "");
	}

	private int managedRefine(long token, long expectedRevision, int type, int predicate,
	                          int compareTarget, String value, String secondValue) {
		if (!isTargetToken(token)) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime changed or ended");
		if (!MemoryEngineContract.isValueType(type)) {
			return managedFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed refine has no supported primitive search type");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		long operationEpoch = cancelEpoch.get();
		long clearGeneration = searchClearGeneration.get();
		try {
			Bundle result = bridge.managedRefine(token, expectedRevision, type,
					predicate, compareTarget, value, secondValue, operationEpoch);
			int code = managedResultCode(result);
			if (code != MemoryEngineContract.RESULT_OK) {
				return consumeManagedResult(token, bridge, result);
			}
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				if (decision != MemoryEngineContract.RESULT_OK) {
					return managedFailure(decision, managedReplyFailureMessage(decision));
				}
				updateManagedState(token, result);
					advanceManagedSearchSession(type);
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedRefresh(long token, long[] ids, boolean passiveRefresh) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge, bridge.managedRefresh(token, ids,
					passiveRefresh ? 0L : managedRevision, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedEdit(long token, long expectedRevision, long[] ids, int declaredType,
	                       String replacementValue, boolean allowWatchOnly) {
		if (!allowWatchOnly && expectedRevision <= 0L) expectedRevision = managedRevision;
		int maxTargets = allowWatchOnly ? MemoryEngineContract.MAX_WATCH_RECORDS
				: MemoryEngineContract.MAX_RESULT_PAGE_SIZE;
		if (ids == null || ids.length == 0 || ids.length > maxTargets) {
			return managedFailure(MemoryEngineContract.RESULT_SAFETY_LIMIT,
					allowWatchOnly ? "Managed Watch edits are limited to 128 rows"
							: "Managed edits are limited to 100 visible result rows");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge, bridge.managedEditTyped(token,
					expectedRevision, ids, declaredType, replacementValue, allowWatchOnly,
					cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedEditInspector(long token, long anchorCandidateId, boolean watchAnchor,
	                                long expectedRevision,
	                                int relativeOffset, int valueType, long expectedBits,
	                                String replacementValue) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			// Preserve the Inspector snapshot revision; never upgrade a stale edit.
			if (watchAnchor ? expectedRevision != 0L : expectedRevision <= 0L) {
				return managedFailure(MemoryEngineContract.RESULT_INVALID_REQUEST,
						"Managed Inspector edit requires its original revision/provenance");
			}
			return consumeManagedResult(token, bridge, bridge.managedEditInspector(token,
				expectedRevision, anchorCandidateId, watchAnchor, relativeOffset, valueType,
				expectedBits, replacementValue, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedAddWatch(long token, long expectedRevision, long[] ids) {
		if (!globalWatchCapacityAvailable(token, ids)) {
			return managedFailure(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
					"The global Watch List limit is 128 rows");
		}
		if (expectedRevision <= 0L) expectedRevision = managedRevision;
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge, bridge.managedAddWatch(token,
					expectedRevision, ids, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedRemoveWatch(long token, long[] ids) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			int code = consumeManagedResult(token, bridge,
					bridge.managedRemoveWatch(token, ids, cancelEpoch.get()));
			if (code == MemoryEngineContract.RESULT_OK) {
				stopFreezeTaskIfIdle();
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedSetWatchLabel(long token, long id, String label) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			return consumeManagedResult(token, bridge,
					bridge.managedSetWatchLabel(token, id, label, cancelEpoch.get()));
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedFreeze(long token, long expectedRevision, long[] ids, int mode,
	                          String firstValue, String secondValue, boolean allowWatchOnly) {
		if (mode != MemoryEngineContract.FREEZE_LOCK) {
			return managedFailure(MemoryEngineContract.RESULT_UNSUPPORTED,
					"Managed Freeze supports Lock only");
		}
		if (!globalWatchCapacityAvailable(token, ids)
				|| !globalFreezeCapacityAvailable(token, ids)) {
			return managedFailure(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
					"The global Watch/Freeze limit would be exceeded");
		}
		if (!allowWatchOnly && expectedRevision <= 0L) expectedRevision = managedRevision;
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			int code = consumeManagedResult(token, bridge,
					bridge.managedSetFreezeLockTyped(token, expectedRevision, ids,
							firstValue, allowWatchOnly, cancelEpoch.get()));
			if (code == MemoryEngineContract.RESULT_OK) startFreezeTaskIfNeeded();
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private int managedClearFreeze(long token, long[] ids) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			int code = consumeManagedResult(token, bridge,
					bridge.managedClearFreeze(token, ids, cancelEpoch.get()));
			if (code == MemoryEngineContract.RESULT_OK) {
				stopFreezeTaskIfIdle();
			}
			return code;
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime connection was lost");
		}
	}

	private Bundle managedResultPage(long token, long expectedRevision, int offset, int limit) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return emptyResultPage();
		long clearGeneration = searchClearGeneration.get();
		long operationEpoch = cancelEpoch.get();
		try {
			Bundle result = bridge.managedResultPage(token, expectedRevision, offset, limit);
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
				return decision == MemoryEngineContract.RESULT_OK ? result : emptyResultPage();
			}
		} catch (RemoteException exception) {
			return emptyResultPage();
		}
	}

	private Bundle managedWatchPage(long token) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return emptyWatchPage();
		try {
			Bundle result = bridge.managedWatchPage(token);
			return acceptManagedRuntimeState(token, bridge, result) ? result : emptyWatchPage();
		} catch (RemoteException exception) {
			return emptyWatchPage();
		}
	}

	private int consumeManagedResult(long token, IMemoryTargetBridge bridge,
	                                @Nullable Bundle result) {
		if (managedResultCode(result) == Integer.MIN_VALUE) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed target returned no operation result");
		}
		try {
			if (!acceptManagedRuntimeState(token, bridge, result)) {
				return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
						"Managed reply belongs to an old runtime generation");
			}
		} catch (RemoteException exception) {
			return managedFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
		updateManagedState(token, result);
		return managedResultCode(result);
	}

	private static int managedResultCode(@Nullable Bundle result) {
		if (result == null || !result.containsKey(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT)) {
			return Integer.MIN_VALUE;
		}
		return result.getInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT,
				MemoryEngineContract.RESULT_TARGET_LOST);
	}

	private boolean acceptManagedRuntimeState(long token, IMemoryTargetBridge bridge,
	                                         @Nullable Bundle state) throws RemoteException {
		if (state == null) return false;
		long replyToken = state.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L);
		long runtimeAfterRpc = bridge.getRuntimeToken();
		synchronized (searchCommitLock) {
			return MemoryManagedServicePolicy.managedReplyFromCurrentRuntime(token, replyToken,
					runtimeAfterRpc, target == bridge);
		}
	}

	private int managedSearchReplyDecisionLocked(long token, IMemoryTargetBridge bridge,
	                                             @Nullable Bundle state, long runtimeAfterRpc,
	                                             long expectedClearGeneration,
	                                             long operationEpoch, long expectedRevision) {
		if (state == null) return MemoryEngineContract.RESULT_TARGET_LOST;
		long replyToken = state.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L);
		long replyRevision = state.getLong(MemoryEngineContract.KEY_MANAGED_REVISION, 0L);
		return MemoryManagedServicePolicy.managedReplyDecision(token, replyToken, runtimeAfterRpc,
				expectedClearGeneration, searchClearGeneration.get(), target == bridge,
				operationEpoch, cancelEpoch.get(), expectedRevision, replyRevision, managedRevision);
	}

	private static String managedReplyFailureMessage(int decision) {
		return switch (decision) {
			case MemoryEngineContract.RESULT_CANCELLED ->
					"Managed reply was invalidated by explicit Clear";
			case MemoryEngineContract.RESULT_IDENTITY_UNSAFE ->
					"Managed search revision changed before the reply could be published";
			default -> "Managed reply belongs to an old runtime generation";
		};
	}

	private int managedFailure(int code, String message) {
		managedLastMessage = message;
		return code;
	}

	private void updateManagedState(long token, @Nullable Bundle state) {
		synchronized (searchCommitLock) {
			updateManagedStateLocked(token, state);
		}
	}

	private void updateManagedStateLocked(long token, @Nullable Bundle state) {
		if (state == null || token == 0L
				|| state.getLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, 0L) != token) return;
		if (managedStateToken != token) {
			managedStateToken = token;
			managedRevision = 0L;
			managedResultCount = 0L;
			managedBaselineCount = 0L;
			managedHistoryDepth = 0;
			managedWatchCount = 0;
			managedFreezeCount = 0;
			managedLastMessage = null;
			clearSearchSession();
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH)) {
			synchronizeCancelEpoch(state.getLong(
					MemoryEngineContract.KEY_MANAGED_CONTROL_EPOCH));
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_SUPPORTED)) {
			managedSupported = state.getBoolean(MemoryEngineContract.KEY_MANAGED_SUPPORTED);
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED)) {
			managedWriteSupported = state.getBoolean(
					MemoryEngineContract.KEY_MANAGED_WRITE_SUPPORTED);
		}
		boolean acceptSearchMetadata = true;
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_REVISION)) {
			long incomingRevision = state.getLong(MemoryEngineContract.KEY_MANAGED_REVISION);
			// Pages and Binder replies can arrive after a newer operation. Revision zero is
			// not a Watch-page sentinel here; only an authoritative clear (which resets the
			// local fields explicitly) may move a nonzero search revision back to zero.
			acceptSearchMetadata = incomingRevision >= managedRevision
					|| (incomingRevision == 0L && managedRevision == 0L);
			if (acceptSearchMetadata) managedRevision = incomingRevision;
		}
		if (acceptSearchMetadata && state.containsKey(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT)) {
			managedResultCount = state.getLong(MemoryEngineContract.KEY_MANAGED_RESULT_COUNT);
		}
		if (acceptSearchMetadata && state.containsKey(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT)) {
			managedBaselineCount = state.getLong(MemoryEngineContract.KEY_MANAGED_BASELINE_COUNT);
		}
		if (acceptSearchMetadata && state.containsKey(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH)) {
			managedHistoryDepth = Math.max(0, state.getInt(
					MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH, managedHistoryDepth));
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT)) {
			managedWatchCount = state.getInt(MemoryEngineContract.KEY_MANAGED_WATCH_COUNT);
		}
		if (state.containsKey(MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT)) {
			managedFreezeCount = Math.max(0, state.getInt(
					MemoryEngineContract.KEY_MANAGED_FREEZE_COUNT, managedFreezeCount));
		}
		if (acceptSearchMetadata) updateManagedSearchSession(state);
		if (state.containsKey(MemoryEngineContract.KEY_MESSAGE)) {
			managedLastMessage = state.getString(MemoryEngineContract.KEY_MESSAGE);
		}
		reconcileFreezeScheduler();
	}

	private void updateManagedSearchSession(@Nullable Bundle state) {
		if (state == null) return;
		synchronized (searchSessionLock) {
			if (state.containsKey(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE)) {
				searchSessionStage = state.getInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
						searchSessionStage);
			}
			if (state.containsKey(MemoryEngineContract.KEY_SEARCH_MODE)) {
				searchSessionMode = state.getInt(MemoryEngineContract.KEY_SEARCH_MODE,
						searchSessionMode);
			}
			if (state.containsKey(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE)) {
				searchRequestedType = state.getInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
						searchRequestedType);
			}
		}
	}

	private void advanceManagedSearchSession(int type) {
		synchronized (searchSessionLock) {
			searchSessionStage = MemoryEngineContract.SEARCH_SESSION_CANDIDATES;
			searchRequestedType = type;
		}
	}

	private int managedSearchType() {
		synchronized (searchSessionLock) {
			return searchRequestedType;
		}
	}

	private static void synchronizeCancelEpoch(long targetEpoch) {
		if (targetEpoch < 0L) return;
		while (true) {
			long current = cancelEpoch.get();
			if (current >= targetEpoch || cancelEpoch.compareAndSet(current, targetEpoch)) return;
		}
	}

	private boolean synchronizeManagedCancelEpoch(long token) {
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return false;
		try {
			if (bridge.getRuntimeToken() != token) return false;
			Bundle state = bridge.getManagedCapabilities(token);
			if (!acceptManagedRuntimeState(token, bridge, state)) return false;
			updateManagedState(token, state);
			return true;
		} catch (RemoteException exception) {
			return false;
		}
	}

	private Bundle inspectManagedCandidate(long token, long candidateId, int radius,
	                                      boolean watchAnchor) {
		if (!isCurrentToken(token)) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"Managed runtime is no longer current");
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
				"MIDlet runtime is not connected");
		try {
			long expectedRevision = watchAnchor ? 0L : managedRevision;
			Bundle result = bridge.managedInspect(token, expectedRevision, candidateId, radius,
					watchAnchor);
			long runtimeAfterRpc = bridge.getRuntimeToken();
			synchronized (searchCommitLock) {
				int decision = managedSearchReplyDecisionLocked(token, bridge, result,
						runtimeAfterRpc, searchClearGeneration.get(), cancelEpoch.get(),
						expectedRevision);
				return decision == MemoryEngineContract.RESULT_OK ? result
						: inspectionFailure(decision, managedReplyFailureMessage(decision));
			}
		} catch (RemoteException exception) {
			return inspectionFailure(MemoryEngineContract.RESULT_TARGET_LOST,
					"MIDlet runtime connection was lost");
		}
	}

	private static Bundle inspectionFailure(int result, @Nullable String message) {
		Bundle bundle = new Bundle();
		bundle.putInt(MemoryEngineContract.KEY_INSPECT_RESULT, result);
		if (message != null && !message.isBlank()) {
			bundle.putString(MemoryEngineContract.KEY_MESSAGE, message);
		}
		return bundle;
	}

	private synchronized void startFreezeTaskIfNeeded() {
		ScheduledFuture<?> current = freezeTask;
		if (current == null || current.isDone()) {
			try {
				freezeTask = freezeScheduler.scheduleWithFixedDelay(
						this::runFreezeTick, 750L, 750L, TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException ignored) {
				freezeTask = null;
			}
		}
	}

	private synchronized void stopFreezeTask() {
		ScheduledFuture<?> current = freezeTask;
		freezeTask = null;
		if (current != null) {
			current.cancel(false);
		}
	}

	private synchronized void stopFreezeTaskIfIdle() {
		if (!MemoryManagedServicePolicy.freezeSchedulerIdle(managedFreezeCount)) {
			return;
		}
		stopFreezeTask();
	}

	private void reconcileFreezeScheduler() {
		if (configuredToken == 0L || managedStateToken != configuredToken) {
			stopFreezeTask();
		} else if (MemoryManagedServicePolicy.freezeSchedulerIdle(managedFreezeCount)) {
			stopFreezeTaskIfIdle();
		} else {
			startFreezeTaskIfNeeded();
		}
	}

	private void runFreezeTick() {
		long token = configuredToken;
		if (token == 0L || !isTargetToken(token)) {
			managedFreezeCount = 0;
			stopFreezeTaskIfIdle();
			return;
		}
		if (managedFreezeCount <= 0) {
			stopFreezeTaskIfIdle();
			return;
		}
		IMemoryTargetBridge bridge = target;
		if (bridge == null) {
			managedFreezeCount = 0;
			stopFreezeTaskIfIdle();
			return;
		}
		try {
			int result = consumeManagedResult(token, bridge,
					bridge.managedFreezeTick(token, cancelEpoch.get()));
			if (result == MemoryEngineContract.RESULT_TARGET_LOST) {
				managedFreezeCount = 0;
			}
		} catch (RemoteException ignored) {
			managedFreezeCount = 0;
		}
		stopFreezeTaskIfIdle();
	}
	private Bundle searchSessionInfo(long token) {
		Bundle bundle = new Bundle();
		boolean current = isCurrentToken(token);
		boolean targetSessionAccepted = false;
		if (current && isManagedCurrent(token)) {
			IMemoryTargetBridge bridge = target;
			if (bridge != null) {
				long clearGeneration = searchClearGeneration.get();
				long operationEpoch = cancelEpoch.get();
				long expectedRevision = managedRevision;
				try {
					Bundle managed = bridge.getManagedSessionInfo(token);
					long runtimeAfterRpc = bridge.getRuntimeToken();
					synchronized (searchCommitLock) {
						int decision = managedSearchReplyDecisionLocked(token, bridge, managed,
								runtimeAfterRpc, clearGeneration, operationEpoch, expectedRevision);
						if (decision == MemoryEngineContract.RESULT_OK && managed != null) {
							updateManagedState(token, managed);
							bundle.putAll(managed);
							targetSessionAccepted = true;
						}
					}
				} catch (RemoteException ignored) {
					// Return the local session snapshot when the target disappears mid-read.
				}
			}
		}
		if (!targetSessionAccepted) {
			synchronized (searchSessionLock) {
				bundle.putInt(MemoryEngineContract.KEY_SEARCH_SESSION_STAGE,
						current ? searchSessionStage : MemoryEngineContract.SEARCH_SESSION_EMPTY);
				bundle.putInt(MemoryEngineContract.KEY_SEARCH_MODE,
						current ? searchSessionMode : MemoryEngineContract.SEARCH_MODE_KNOWN);
				bundle.putInt(MemoryEngineContract.KEY_SEARCH_REQUESTED_TYPE,
						current ? searchRequestedType : MemoryEngineContract.TYPE_AUTO);
				bundle.putInt(MemoryEngineContract.KEY_SEARCH_HISTORY_DEPTH,
						current ? managedHistoryDepth : 0);
			}
		}
		return bundle;
	}

	private void resetSearchSession(int stage, int mode, int requestedType) {
		synchronized (searchSessionLock) {
			searchSessionStage = stage;
			searchSessionMode = mode;
			searchRequestedType = requestedType;
		}
	}

	static int restoreSearchTypeHistory(ArrayDeque<Integer> history, int currentType) {
		return history.isEmpty() ? currentType : history.removeLast();
	}

	private void clearSearchSession() {
		synchronized (searchSessionLock) {
			searchSessionStage = MemoryEngineContract.SEARCH_SESSION_EMPTY;
			searchSessionMode = MemoryEngineContract.SEARCH_MODE_KNOWN;
			searchRequestedType = MemoryEngineContract.TYPE_AUTO;
		}
	}

	private boolean isCurrentToken(long token) {
		if (token == 0L || token != configuredToken) {
			return false;
		}
		return isTargetToken(token);
	}

	/** Enforces the one 128-row Watch capacity for Managed Java candidates. */
	private boolean globalWatchCapacityAvailable(long token, @Nullable long[] requested) {
		if (requested == null || requested.length == 0
				|| requested.length > MemoryEngineContract.MAX_WATCH_RECORDS) return false;
		Set<Long> existing = new HashSet<>();
		Bundle managed = managedWatchPage(token);
		long[] managedIds = watchLongs(managed, MemoryEngineContract.KEY_WATCH_IDS);
		for (long id : managedIds) existing.add(id);
		Set<Long> unique = new HashSet<>();
		for (long value : requested) unique.add(value);
		int additional = 0;
		for (long id : unique) if (!existing.contains(id)) additional++;
		return existing.size() + additional <= MemoryEngineContract.MAX_WATCH_RECORDS;
	}

	private boolean globalFreezeCapacityAvailable(long token, @Nullable long[] requested) {
		if (requested == null || requested.length == 0
				|| requested.length > MemoryEngineContract.MAX_FREEZE_RECORDS) return false;
		IMemoryTargetBridge bridge = target;
		if (bridge == null) return false;
		try {
			Bundle state = bridge.getManagedCapabilities(token);
			if (!acceptManagedRuntimeState(token, bridge, state)) return false;
			updateManagedState(token, state);
		} catch (RemoteException ignored) {
			return false;
		}
		Set<Long> frozen = new HashSet<>();
		Bundle managed = managedWatchPage(token);
		long[] managedIds = watchLongs(managed, MemoryEngineContract.KEY_WATCH_IDS);
		int[] managedModes = managed == null ? null
				: managed.getIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES);
		if (managedModes != null && managedModes.length == managedIds.length) {
			for (int index = 0; index < managedIds.length; index++) {
				if (managedModes[index] >= MemoryEngineContract.FREEZE_LOCK) {
					frozen.add(managedIds[index]);
				}
			}
		}
		Set<Long> unique = new HashSet<>();
		for (long id : requested) unique.add(id);
		int additional = 0;
		for (long id : unique) if (!frozen.contains(id)) additional++;
		return frozen.size() + additional <= MemoryEngineContract.MAX_FREEZE_RECORDS;
	}

	private boolean isManagedCurrent(long token) {
		return configuredToken == token && isCurrentToken(token);
	}

	private static boolean containsManagedIds(@Nullable long[] ids) {
		if (ids == null) return false;
		for (long id : ids) if (ManagedJavaMemoryIds.isManaged(id)) return true;
		return false;
	}

	private static boolean allManagedIds(@Nullable long[] ids) {
		if (ids == null || ids.length == 0) return false;
		for (long id : ids) if (!ManagedJavaMemoryIds.hasValidNamespace(id)) return false;
		return true;
	}

	private boolean isTargetToken(long token) {
		IMemoryTargetBridge bridge = target;
		if (token == 0L || bridge == null) {
			return false;
		}
		try {
			return bridge.getRuntimeToken() == token;
		} catch (RemoteException exception) {
			return false;
		}
	}

	private void invalidateTarget() {
		synchronized (searchCommitLock) {
			observedRuntimeToken = 0L;
			configuredToken = 0L;
			managedSupported = false;
			managedWriteSupported = false;
			managedStateToken = 0L;
			managedRevision = 0L;
			managedResultCount = 0L;
			managedBaselineCount = 0L;
			managedHistoryDepth = 0;
			managedWatchCount = 0;
			managedFreezeCount = 0;
			managedLastMessage = null;
			searchClearGeneration.incrementAndGet();
			clearSearchSession();
			cancelEpoch.incrementAndGet();
		}
		stopFreezeTaskIfIdle();
		notifyLocalRuntimeUnavailable();
	}

	private void notifyFinished(long operationId, long token, int result,
	                            @Nullable String serviceMessage, boolean passiveRefresh,
	                            boolean searchOperation) {
		boolean managedVisible = configuredToken == token;
		long count = managedVisible ? managedResultCount : 0L;
		String message = serviceMessage != null ? serviceMessage : managedLastMessage;
		int callbackCount = callbacks.beginBroadcast();
		try {
			for (int index = 0; index < callbackCount; index++) {
				try {
					callbacks.getBroadcastItem(index)
							.onOperationFinished(operationId, result, count, message,
									passiveRefresh, searchOperation);
				} catch (RemoteException ignored) {
					// RemoteCallbackList removes dead clients.
				}
			}
		} finally {
			callbacks.finishBroadcast();
		}
	}

	private static Bundle emptyResultPage() {
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_RESULT_IDS, new long[0]);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_RESULT_ADDRESSES, new String[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_ALIAS_MASKS, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_TYPES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_STATES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_RESULT_RELOCATIONS, new int[0]);
		return result;
	}

	private static Bundle emptyWatchPage() {
		Bundle result = new Bundle();
		result.putLongArray(MemoryEngineContract.KEY_WATCH_IDS, new long[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_INITIAL_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_PREVIOUS_VALUES, new String[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_ADDRESSES, new String[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_TYPES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_STATES, new int[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_RELOCATIONS, new int[0]);
		result.putStringArray(MemoryEngineContract.KEY_WATCH_LABELS, new String[0]);
		result.putIntArray(MemoryEngineContract.KEY_WATCH_FREEZE_MODES, new int[0]);
		result.putBooleanArray(MemoryEngineContract.KEY_WATCH_FREEZE_PAUSED, new boolean[0]);
		return result;
	}

	private static long[] watchLongs(@Nullable Bundle bundle, String key) {
		long[] values = bundle == null ? null : bundle.getLongArray(key);
		return values == null ? new long[0] : values;
	}

	private interface Operation {
		int run();
	}
}
